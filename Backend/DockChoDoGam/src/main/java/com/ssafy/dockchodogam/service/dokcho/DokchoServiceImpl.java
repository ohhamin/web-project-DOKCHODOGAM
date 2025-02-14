package com.ssafy.dockchodogam.service.dokcho;

import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.util.IOUtils;
import com.ssafy.dockchodogam.domain.*;
import com.ssafy.dockchodogam.dto.exception.plant.PlantNotFoundException;
import com.ssafy.dockchodogam.dto.exception.user.UserNotFoundException;
import com.ssafy.dockchodogam.dto.plant.ArchiveResponseDto;
import com.ssafy.dockchodogam.dto.plant.PlantDetailDto;
import com.ssafy.dockchodogam.dto.plant.PlantListDto;
import com.ssafy.dockchodogam.dto.plant.TodayPlantDto;
import com.ssafy.dockchodogam.repository.*;
import com.ssafy.dockchodogam.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;


@Service
@RequiredArgsConstructor
@Transactional
public class DokchoServiceImpl implements DokchoService {

    private final String uploadPath = "~/images/";
    private final PlantRepository plantRepository;
    private final UserRepository userRepository;
    private final UserMonsterRepository userMonsterRepository;
    private final TodayPlantRepository todayPlantRepository;
    private final AmazonS3Client amazonS3Client;
    private final PlantMongoRepository plantMongoRepository;
    private final MonsterRepository monsterRepository;
    private final ArchiveRepository archiveRepository;
    @Value("${cloud.aws.s3.bucket}")
    private String bucketName;
    @Value("${plant.api.key}")
    private String apiKey;

    @Override
    public List<PlantListDto> findAllPlants(int page) {
        List<PlantListDto> plantList = plantRepository.findAll(PageRequest.of(page, 20)).stream()
                .map(p -> new PlantListDto(p.getPlantId(), p.getName(), p.getEngNm(), p.getImgUrl(),
                        p.getFamilyKorNm(), p.getGenusKorNm(), p.getPlantSpecsScnm()))
                .collect(Collectors.toList());
        return plantList;
    }

    @Override
    public PlantDetailDto findPlantDetail(Long plantId) {
        Plant plant = plantRepository.findPlantByPlantId(plantId).orElseThrow(PlantNotFoundException::new);
        return PlantDetailDto.from(plant);
    }

    @Override
    public List<PlantListDto> searchPlants(String keyword, Integer page, Integer size) {
        PageRequest pageRequest = PageRequest.of(page, size);
        List<PlantListDto> plantList = plantRepository.searchPlantsByKeyword(keyword, pageRequest).stream()
                .map(p -> new PlantListDto(p.getPlantId(), p.getName(), p.getEngNm(), p.getImgUrl(),
                        p.getFamilyKorNm(), p.getGenusKorNm(), p.getPlantSpecsScnm()))
                .collect(Collectors.toList());
        return plantList;
    }

    @Override
    public void addFoundMonster(Monster monster) {
        User user = SecurityUtil.getCurrentUsername().flatMap(userRepository::findByUsername).orElseThrow(UserNotFoundException::new);

        UserMonster userMonster = UserMonster.builder().user(user).monster(monster).build();
        userMonsterRepository.save(userMonster);
    }

    @Override
    public PlantDetailDto findPlantByPlantSpecsScnm(String plantSpecsScnm) {
        Plant plant = plantRepository.findByplantSpecsScnm(plantSpecsScnm).orElseThrow(PlantNotFoundException::new);
        return new PlantDetailDto().from(plant);
    }

    @Override
    public String savePlantImage(MultipartFile file) {
        if (file.isEmpty()){
            throw new RuntimeException("이미지가 없습니다.");
        }
        String fileName = file.getOriginalFilename();
        ObjectMetadata objectMetadata = new ObjectMetadata();
        objectMetadata.setContentType(file.getContentType());

        try(InputStream inputStream = file.getInputStream()){
            amazonS3Client.putObject(new PutObjectRequest(bucketName, fileName, inputStream, objectMetadata)
                    .withCannedAcl(CannedAccessControlList.PublicRead));
        }catch (IOException e){
            throw new RuntimeException("이미지 업로드 실패");
        }

        return amazonS3Client.getUrl(bucketName, fileName).toString();
    }

    @Override
    public Map<String, Object> judgeImage(String imgurl) throws Exception {
        String jsonData = sendAPIRequest(imgurl);

        // MongoDB에 저장
        plantMongoRepository.save(new JSONObject(jsonData));

        saveArchive(jsonData);
        Map<String, Object> data = getGenusAndProb(jsonData);

        double probability = (double) data.get("probability");
        Map<String, Object> res = new HashMap<>();
        res.put("plantExist", false);
        res.put("plant", null);
        if (probability < 0.4) {
            res.put("errCode", "1: low prob");
            return res;
        }
        res.put("probability", probability);
        if (data.containsKey("species")) {
            String species = (String) data.get("species");
            Optional<Plant> plantBySpecies = plantRepository.findPlantByEngNm(species);
            if (plantBySpecies.isPresent()) {
                Plant plant = plantBySpecies.get();
                res.put("plantExist", true);
                res.put("plant", plant);
                return res;
            } else {
                String genus = (String) data.get("genus");
                List<Plant> plantByGenus = plantRepository.findPlantsByGenusNm(genus);
                if (plantByGenus.isEmpty()) {
                    res.put("errCode", "2: no plant in species & genus");
                } else {
                    Plant plant = plantByGenus.get(0);
                    res.put("plantExist", true);
                    res.put("plant", plant);
                }
            }
        } else {
            String genus = (String) data.get("genus");
            List<Plant> plantByGenus = plantRepository.findPlantsByGenusNm(genus);
            if (plantByGenus.isEmpty()) {
                res.put("errCode", "3: no plant in genus");
            } else {
                Plant plant = plantByGenus.get(0);
                res.put("plantExist", true);
                res.put("plant", plant);
            }
        }

        return res;
    }

    public boolean checkUserDogam(Long monsterId){
        User user = SecurityUtil.getCurrentUsername().flatMap(userRepository::findByUsername).orElseThrow(UserNotFoundException::new);
        Monster monster = monsterRepository.findMonsterByMonsterId(monsterId);
        Optional<UserMonster> um = userMonsterRepository.findUserMonsterByMonsterAndUser(monster, user);

        if (um.isPresent()) {
            return true;
        } else{
            return false;
        }
    }

    @Override
    public List<ArchiveResponseDto> getArchives(Pageable pageable) {
        User user = SecurityUtil.getCurrentUsername().flatMap(userRepository::findByUsername).orElseThrow(UserNotFoundException::new);
        List<ArchiveResponseDto> archiveDtos = archiveRepository.findArchivesByUserNicknameOrderByArchiveIdDesc(
                pageable, user.getNickname()).stream().map(a -> ArchiveResponseDto.from(a))
                .collect(Collectors.toList());
        return archiveDtos;
    }

    private static String base64EncodeFromFile(String imgurl) throws Exception {
        InputStream is = new URL(imgurl).openStream();
        byte[] bytes = IOUtils.toByteArray(is);
        String base64img = Base64.getEncoder().encodeToString(bytes);
        return base64img;
    }

    public static String sendPostRequest(String urlString, JSONObject data) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection con = (HttpURLConnection) url.openConnection();

        con.setDoOutput(true);
        con.setDoInput(true);
        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", "application/json");

        OutputStream os = con.getOutputStream();
        os.write(data.toString().getBytes());
        os.close();

        InputStream is = con.getInputStream();
        byte[] bytes = IOUtils.toByteArray(is);
        String response = new String(bytes);

//        System.out.println("Response code : " + con.getResponseCode());
//        System.out.println("Response : " + response);
        con.disconnect();
        return response;
    }

    public String sendAPIRequest(String path) throws Exception{

        // read image from local file system and encode
        JSONObject data = new JSONObject();
        data.put("api_key", apiKey);

        // add images
        JSONArray images = new JSONArray();
        String fileData = base64EncodeFromFile(path);
        images.put(fileData);

        data.put("images", images);

        // add modifiers
        // modifiers info: https://github.com/flowerchecker/Plant-id-API/wiki/Modifiers
        JSONArray modifiers = new JSONArray()
                .put("crops_fast")
                .put("similar_images");
        data.put("modifiers", modifiers);

        // add language
        data.put("plant_language", "ko");

        // add plant details
        // more info here: https://github.com/flowerchecker/Plant-id-API/wiki/Plant-details
        JSONArray plantDetails = new JSONArray()
                .put("common_names")
                .put("url")
                .put("name_authority")
                .put("wiki_description")
                .put("taxonomy")
                .put("synonyms");
        data.put("plant_details", plantDetails);

        return sendPostRequest("https://api.plant.id/v2/identify", data);
    }

    public Map<String, Object> getGenusAndProb(String data) throws JSONException {
        Map<String, Object> res = new HashMap<>();
        JSONObject jsonObject = new JSONObject(data);
        JSONArray suggestions = (JSONArray) jsonObject.get("suggestions");
        JSONObject candidate = (JSONObject) suggestions.get(0);
        double probability = (double) candidate.get("probability");
        JSONObject plant_details = (JSONObject) candidate.get("plant_details");
        JSONObject structured_name = (JSONObject) plant_details.get("structured_name");
        String genus = (String) structured_name.get("genus");
        if (structured_name.has("species")) {
            String species = (String) structured_name.get("species");
            res.put("species", species);
        }

        res.put("probability", probability);
        res.put("genus", genus);


        return res;
    }

    public TodayPlantDto getTodayPlant(){
        String today = LocalDate.now().toString();
        int month = Integer.parseInt(today.substring(5, 7));
        int day = Integer.parseInt(today.substring(8, 10));

        return TodayPlantDto.of(todayPlantRepository.findByMonthAndDay(month, day).orElseThrow(PlantNotFoundException::new));
    }

    public void saveArchive(String jsonData) throws JSONException {
        JSONObject jsonObject = new JSONObject(jsonData);
        JSONArray images = (JSONArray) jsonObject.get("images");
        JSONObject image = (JSONObject) images.get(0);
        String imgURL = (String) image.get("url");
        boolean is_plant = (boolean) jsonObject.get("is_plant");
        JSONArray suggestions = (JSONArray) jsonObject.get("suggestions");
        System.out.println(1);
        JSONObject suggestion = (JSONObject) suggestions.get(0);
        String name;
        try {
            JSONObject plant_detail = (JSONObject) suggestion.get("plant_detail");
            JSONArray common_names = (JSONArray) plant_detail.get("common_names");
            name = (String) common_names.get(0);
        } catch (Exception e) {
            name = (String) suggestion.get("plant_name");
        }
        double probability = (double) suggestion.get("probability");
        JSONArray similar_images = (JSONArray) suggestion.get("similar_images");
        System.out.println(4);
        JSONObject similar_image = (JSONObject) similar_images.get(0);
        String similar_img = (String) similar_image.get("url");

        User user = SecurityUtil.getCurrentUsername().flatMap(userRepository::findByUsername).orElseThrow(UserNotFoundException::new);

        System.out.println(user.getNickname());
        Archive archive = new Archive().builder()
                .imgURL(imgURL)
                .userNickname(user.getNickname())
                .isPlant(is_plant)
                .probability(probability)
                .suggestionPlantNm(name)
                .suggestionImg(similar_img)
                .build();

        archiveRepository.save(archive);
    }
}
